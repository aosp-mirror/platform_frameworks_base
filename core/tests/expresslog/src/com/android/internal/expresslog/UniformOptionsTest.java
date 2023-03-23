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

import androidx.test.filters.SmallTest;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
@SmallTest
public class UniformOptionsTest {
    private static final String TAG = UniformOptionsTest.class.getSimpleName();

    @Test
    public void testGetBinsCount() {
        Histogram.UniformOptions options1 = new Histogram.UniformOptions(1, 100, 1000);
        assertEquals(3, options1.getBinsCount());

        Histogram.UniformOptions options10 = new Histogram.UniformOptions(10, 100, 1000);
        assertEquals(12, options10.getBinsCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructZeroBinsCount() {
        new Histogram.UniformOptions(0, 100, 1000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructNegativeBinsCount() {
        new Histogram.UniformOptions(-1, 100, 1000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructMaxValueLessThanMinValue() {
        new Histogram.UniformOptions(10, 1000, 100);
    }

    @Test
    public void testBinIndexForRangeEqual1() {
        Histogram.UniformOptions options = new Histogram.UniformOptions(10, 1, 11);
        for (int i = 0, bins = options.getBinsCount(); i < bins; i++) {
            assertEquals(i, options.getBinForSample(i));
        }
    }

    @Test
    public void testBinIndexForRangeEqual2() {
        Histogram.UniformOptions options = new Histogram.UniformOptions(10, 1, 21);
        for (int i = 0, bins = options.getBinsCount(); i < bins; i++) {
            assertEquals(i, options.getBinForSample(i * 2));
            assertEquals(i, options.getBinForSample(i * 2 - 1));
        }
    }

    @Test
    public void testBinIndexForRangeEqual5() {
        Histogram.UniformOptions options = new Histogram.UniformOptions(2, 0, 10);
        assertEquals(4, options.getBinsCount());
        for (int i = 0; i < 2; i++) {
            for (int sample = 0; sample < 5; sample++) {
                assertEquals(i + 1, options.getBinForSample(i * 5 + sample));
            }
        }
    }

    @Test
    public void testBinIndexForRangeEqual10() {
        Histogram.UniformOptions options = new Histogram.UniformOptions(10, 1, 101);
        assertEquals(0, options.getBinForSample(0));
        assertEquals(options.getBinsCount() - 2, options.getBinForSample(100));
        assertEquals(options.getBinsCount() - 1, options.getBinForSample(101));

        final float binSize = (101 - 1) / 10f;
        for (int i = 1, bins = options.getBinsCount() - 1; i < bins; i++) {
            assertEquals(i, options.getBinForSample(i * binSize));
        }
    }

    @Test
    public void testBinIndexForRangeEqual90() {
        final int binCount = 10;
        final int minValue = 100;
        final int maxValue = 100000;

        Histogram.UniformOptions options = new Histogram.UniformOptions(binCount, minValue,
                maxValue);

        // logging underflow sample
        assertEquals(0, options.getBinForSample(minValue - 1));

        // logging overflow sample
        assertEquals(binCount + 1, options.getBinForSample(maxValue));
        assertEquals(binCount + 1, options.getBinForSample(maxValue + 1));

        // logging min edge sample
        assertEquals(1, options.getBinForSample(minValue));

        // logging max edge sample
        assertEquals(binCount, options.getBinForSample(maxValue - 1));

        // logging single valid sample per bin
        final int binSize = (maxValue - minValue) / binCount;

        for (int i = 0; i < binCount; i++) {
            assertEquals(i + 1, options.getBinForSample(minValue + binSize * i));
        }
    }
}
