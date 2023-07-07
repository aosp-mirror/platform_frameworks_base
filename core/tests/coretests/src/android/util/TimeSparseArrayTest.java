/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.util;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link TimeSparseArray}.
 * This class only tests subclass specific functionality. Tests for the super class
 * {@link LongSparseArray} should be covered under {@link LongSparseArrayTest}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TimeSparseArrayTest {

    @Test
    public void closestIndexOnOrAfter() {
        final TimeSparseArray<Object> timeSparseArray = new TimeSparseArray<>();

        // Values don't matter for this test.
        timeSparseArray.put(51, new Object());
        timeSparseArray.put(10, new Object());
        timeSparseArray.put(59, new Object());

        assertThat(timeSparseArray.size()).isEqualTo(3);

        // Testing any number arbitrarily smaller than 10.
        assertThat(timeSparseArray.closestIndexOnOrAfter(-141213)).isEqualTo(0);
        for (long time = -43; time <= 10; time++) {
            assertThat(timeSparseArray.closestIndexOnOrAfter(time)).isEqualTo(0);
        }

        for (long time = 11; time <= 51; time++) {
            assertThat(timeSparseArray.closestIndexOnOrAfter(time)).isEqualTo(1);
        }

        for (long time = 52; time <= 59; time++) {
            assertThat(timeSparseArray.closestIndexOnOrAfter(time)).isEqualTo(2);
        }

        for (long time = 60; time <= 102; time++) {
            assertThat(timeSparseArray.closestIndexOnOrAfter(time)).isEqualTo(3);
        }
        // Testing any number arbitrarily larger than 59.
        assertThat(timeSparseArray.closestIndexOnOrAfter(15332)).isEqualTo(3);
    }

    @Test
    public void closestIndexOnOrBefore() {
        final TimeSparseArray<Object> timeSparseArray = new TimeSparseArray<>();

        // Values don't matter for this test.
        timeSparseArray.put(21, new Object());
        timeSparseArray.put(4, new Object());
        timeSparseArray.put(91, new Object());
        timeSparseArray.put(39, new Object());

        assertThat(timeSparseArray.size()).isEqualTo(4);

        // Testing any number arbitrarily smaller than 4.
        assertThat(timeSparseArray.closestIndexOnOrBefore(-1478133)).isEqualTo(-1);
        for (long time = -42; time < 4; time++) {
            assertThat(timeSparseArray.closestIndexOnOrBefore(time)).isEqualTo(-1);
        }

        for (long time = 4; time < 21; time++) {
            assertThat(timeSparseArray.closestIndexOnOrBefore(time)).isEqualTo(0);
        }

        for (long time = 21; time < 39; time++) {
            assertThat(timeSparseArray.closestIndexOnOrBefore(time)).isEqualTo(1);
        }

        for (long time = 39; time < 91; time++) {
            assertThat(timeSparseArray.closestIndexOnOrBefore(time)).isEqualTo(2);
        }

        for (long time = 91; time < 109; time++) {
            assertThat(timeSparseArray.closestIndexOnOrBefore(time)).isEqualTo(3);
        }
        // Testing any number arbitrarily larger than 91.
        assertThat(timeSparseArray.closestIndexOnOrBefore(1980732)).isEqualTo(3);
    }
}
