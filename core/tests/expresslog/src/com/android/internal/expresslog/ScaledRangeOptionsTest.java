/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.expresslog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@SmallTest
public class ScaledRangeOptionsTest {
    private static final String TAG = ScaledRangeOptionsTest.class.getSimpleName();

    @Test
    public void testGetBinsCount() {
        Histogram.ScaledRangeOptions options1 = new Histogram.ScaledRangeOptions(1, 100, 100, 2);
        assertEquals(3, options1.getBinsCount());

        Histogram.ScaledRangeOptions options10 = new Histogram.ScaledRangeOptions(10, 100, 100, 2);
        assertEquals(12, options10.getBinsCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructZeroBinsCount() {
        new Histogram.ScaledRangeOptions(0, 100, 100, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructNegativeBinsCount() {
        new Histogram.ScaledRangeOptions(-1, 100, 100, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructNegativeFirstBinWidth() {
        new Histogram.ScaledRangeOptions(10, 100, -100, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructTooSmallFirstBinWidth() {
        new Histogram.ScaledRangeOptions(10, 100, 0.5f, 2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructNegativeScaleFactor() {
        new Histogram.ScaledRangeOptions(10, 100, 100, -2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructTooSmallScaleFactor() {
        new Histogram.ScaledRangeOptions(10, 100, 100, 0.5f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructTooBigScaleFactor() {
        new Histogram.ScaledRangeOptions(10, 100, 100, 500.f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructTooBigBinRange() {
        new Histogram.ScaledRangeOptions(100, 100, 100, 10.f);
    }

    @Test
    public void testBinIndexForRangeEqual1() {
        Histogram.ScaledRangeOptions options = new Histogram.ScaledRangeOptions(10, 1, 1, 1);
        assertEquals(12, options.getBinsCount());

        assertEquals(11, options.getBinForSample(11));

        for (int i = 0, bins = options.getBinsCount(); i < bins; i++) {
            assertEquals(i, options.getBinForSample(i));
        }
    }

    @Test
    public void testBinIndexForRangeEqual2() {
        // this should produce bin otpions similar to linear histogram with bin width 2
        Histogram.ScaledRangeOptions options = new Histogram.ScaledRangeOptions(10, 1, 2, 1);
        assertEquals(12, options.getBinsCount());

        for (int i = 0, bins = options.getBinsCount(); i < bins; i++) {
            assertEquals(i, options.getBinForSample(i * 2));
            assertEquals(i, options.getBinForSample(i * 2 - 1));
        }
    }

    @Test
    public void testBinIndexForRangeEqual5() {
        Histogram.ScaledRangeOptions options = new Histogram.ScaledRangeOptions(2, 0, 5, 1);
        assertEquals(4, options.getBinsCount());
        for (int i = 0; i < 2; i++) {
            for (int sample = 0; sample < 5; sample++) {
                assertEquals(i + 1, options.getBinForSample(i * 5 + sample));
            }
        }
    }

    @Test
    public void testBinIndexForRangeEqual10() {
        Histogram.ScaledRangeOptions options = new Histogram.ScaledRangeOptions(10, 1, 10, 1);
        assertEquals(0, options.getBinForSample(0));
        assertEquals(options.getBinsCount() - 2, options.getBinForSample(100));
        assertEquals(options.getBinsCount() - 1, options.getBinForSample(101));

        final float binSize = (101 - 1) / 10f;
        for (int i = 1, bins = options.getBinsCount() - 1; i < bins; i++) {
            assertEquals(i, options.getBinForSample(i * binSize));
        }
    }

    @Test
    public void testBinIndexForScaleFactor2() {
        final int binsCount = 10;
        final int minValue = 10;
        final int firstBinWidth = 5;
        final int scaledFactor = 2;

        Histogram.ScaledRangeOptions options = new Histogram.ScaledRangeOptions(
                binsCount, minValue, firstBinWidth, scaledFactor);
        assertEquals(binsCount + 2, options.getBinsCount());
        long[] binCounts = new long[10];

        // precalculate max valid value - start value for the overflow bin
        int lastBinStartValue = minValue; //firstBinMin value
        int lastBinWidth = firstBinWidth;
        for (int binIdx = 2; binIdx <= binsCount + 1; binIdx++) {
            lastBinStartValue = lastBinStartValue + lastBinWidth;
            lastBinWidth *= scaledFactor;
        }

        // underflow bin
        for (int i = 1; i < minValue; i++) {
            assertEquals(0, options.getBinForSample(i));
        }

        for (int i = 10; i < lastBinStartValue; i++) {
            assertTrue(options.getBinForSample(i) > 0);
            assertTrue(options.getBinForSample(i) <= binsCount);
            binCounts[options.getBinForSample(i) - 1]++;
        }

        // overflow bin
        assertEquals(binsCount + 1, options.getBinForSample(lastBinStartValue));

        for (int i = 1; i < binsCount; i++) {
            assertEquals(binCounts[i], binCounts[i - 1] * 2L);
        }
    }
}
