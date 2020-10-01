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
}
