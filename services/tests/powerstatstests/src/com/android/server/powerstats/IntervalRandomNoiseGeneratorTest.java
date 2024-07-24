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

package com.android.server.powerstats;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.function.Supplier;

public class IntervalRandomNoiseGeneratorTest {

    @Test
    public void parameterizedDistribution() {
         // Assert closeness to theoretical distribution
        assertDistribution(3.0,
                0.0392,
                0.2617,
                0.6990);

        assertDistribution(5.0,
                0.0003,
                0.0098,
                0.0676,
                0.2502,
                0.6720);

        assertDistribution(9.0,
                0.0000,
                0.0002,
                0.0097,
                0.1242,
                0.8658);
    }

    private void assertDistribution(double alpha, Double... expectedBuckets) {
        IntervalRandomNoiseGenerator generator = new IntervalRandomNoiseGenerator(alpha);
        generator.reseed(42);  // Make test repeatable
        final int sampleCount = 1000;
        final int bucketCount = expectedBuckets.length;
        int[] histogram = buildHistogram(() -> {
            generator.refresh();
            return generator.addNoise(100, 200, 12345);
        }, sampleCount, bucketCount, 100, 200);

        for (int i = 0; i < expectedBuckets.length; i++) {
            assertWithMessage("Bucket #" + i)
                    .that((double) histogram[i] / sampleCount)
                    .isWithin(0.05)
                    .of(expectedBuckets[i]);
        }
    }

    @NotNull
    private int[] buildHistogram(Supplier<Long> generator, int sampleCount,
            int bucketCount, int lowBound, int highBound) {
        int[] buckets = new int[bucketCount];
        for (int i = 0; i < sampleCount; i++) {
            long sample = generator.get();
            assertThat(sample).isAtLeast(lowBound);
            assertThat(sample).isAtMost(highBound);
            buckets[(int) ((double) (sample - lowBound) / (highBound - lowBound) * bucketCount)]++;
        }
        return buckets;
    }

    @Test
    public void stickiness() {
        IntervalRandomNoiseGenerator generator = new IntervalRandomNoiseGenerator(9);
        generator.reseed(42);  // Make test repeatable

        long value1a = generator.addNoise(1000, 5000, 123);
        long value1b = generator.addNoise(1000, 5000, 123);
        long value1c = generator.addNoise(1000, 5000, 123);
        assertThat(value1b).isEqualTo(value1a);
        assertThat(value1c).isEqualTo(value1a);

        // Different stickyKey
        long value2a = generator.addNoise(1000, 5000, 321);
        long value2b = generator.addNoise(1000, 5000, 321);
        assertThat(value2a).isNotEqualTo(value1a);
        assertThat(value2b).isEqualTo(value2a);

        generator.refresh();

        // Same stickyKey after a refresh - different value
        long value3 = generator.addNoise(1000, 5000, 123);
        long value4 = generator.addNoise(1000, 5000, 321);
        assertThat(value3).isNotEqualTo(value1a);
        assertThat(value4).isNotEqualTo(value2a);
    }
}
