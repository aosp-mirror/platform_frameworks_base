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

import com.android.internal.util.function.LongObjPredicate;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link LongSparseArray}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LongSparseArrayTest {
    @Test
    public void testRemoveIf() {
        final LongSparseArray<Integer> sparseArray = new LongSparseArray();
        for (int i = 0; i < 10; ++i) {
            for (int j = 100; j < 110; ++j) {
                sparseArray.put(i, j);
                sparseArray.put(-i, j);
                sparseArray.put(j, -i);
                sparseArray.put(-j, -i);
            }
        }

        final LongObjPredicate<Integer> predicate = (value, obj) -> (value < 0 && obj < 0);
        sparseArray.removeIf(predicate);

        for (int i = 0; i < sparseArray.size(); ++i) {
            assertThat(predicate.test(sparseArray.keyAt(i), sparseArray.valueAt(i)))
                    .isFalse();
        }
    }

    @Test
    public void firstIndexOnOrAfter() {
        final LongSparseArray<Object> longSparseArray = new LongSparseArray<>();

        // Values don't matter for this test.
        longSparseArray.put(51, new Object());
        longSparseArray.put(10, new Object());
        longSparseArray.put(59, new Object());

        assertThat(longSparseArray.size()).isEqualTo(3);

        // Testing any number arbitrarily smaller than 10.
        assertThat(longSparseArray.firstIndexOnOrAfter(-141213)).isEqualTo(0);
        for (long time = -43; time <= 10; time++) {
            assertThat(longSparseArray.firstIndexOnOrAfter(time)).isEqualTo(0);
        }

        for (long time = 11; time <= 51; time++) {
            assertThat(longSparseArray.firstIndexOnOrAfter(time)).isEqualTo(1);
        }

        for (long time = 52; time <= 59; time++) {
            assertThat(longSparseArray.firstIndexOnOrAfter(time)).isEqualTo(2);
        }

        for (long time = 60; time <= 102; time++) {
            assertThat(longSparseArray.firstIndexOnOrAfter(time)).isEqualTo(3);
        }
        // Testing any number arbitrarily larger than 59.
        assertThat(longSparseArray.firstIndexOnOrAfter(15332)).isEqualTo(3);
    }

    @Test
    public void lastIndexOnOrBefore() {
        final LongSparseArray<Object> longSparseArray = new LongSparseArray<>();

        // Values don't matter for this test.
        longSparseArray.put(21, new Object());
        longSparseArray.put(4, new Object());
        longSparseArray.put(91, new Object());
        longSparseArray.put(39, new Object());

        assertThat(longSparseArray.size()).isEqualTo(4);

        // Testing any number arbitrarily smaller than 4.
        assertThat(longSparseArray.lastIndexOnOrBefore(-1478133)).isEqualTo(-1);
        for (long time = -42; time < 4; time++) {
            assertThat(longSparseArray.lastIndexOnOrBefore(time)).isEqualTo(-1);
        }

        for (long time = 4; time < 21; time++) {
            assertThat(longSparseArray.lastIndexOnOrBefore(time)).isEqualTo(0);
        }

        for (long time = 21; time < 39; time++) {
            assertThat(longSparseArray.lastIndexOnOrBefore(time)).isEqualTo(1);
        }

        for (long time = 39; time < 91; time++) {
            assertThat(longSparseArray.lastIndexOnOrBefore(time)).isEqualTo(2);
        }

        for (long time = 91; time < 109; time++) {
            assertThat(longSparseArray.lastIndexOnOrBefore(time)).isEqualTo(3);
        }
        // Testing any number arbitrarily larger than 91.
        assertThat(longSparseArray.lastIndexOnOrBefore(1980732)).isEqualTo(3);
    }
}
