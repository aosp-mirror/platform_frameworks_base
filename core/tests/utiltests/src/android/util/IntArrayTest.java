/*
 * Copyright (C) 2017 The Android Open Source Project
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
import static com.google.common.truth.Truth.assertWithMessage;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class IntArrayTest {

    @Test
    public void testIntArray() {
        IntArray a = new IntArray();
        a.add(1);
        a.add(2);
        a.add(3);
        verify(a, 1, 2, 3);

        IntArray b = IntArray.fromArray(new int[]{4, 5, 6, 7, 8}, 3);
        a.addAll(b);
        verify(a, 1, 2, 3, 4, 5, 6);

        a.resize(2);
        verify(a, 1, 2);

        a.resize(8);
        verify(a, 1, 2, 0, 0, 0, 0, 0, 0);

        a.set(5, 10);
        verify(a, 1, 2, 0, 0, 0, 10, 0, 0);

        a.add(5, 20);
        assertThat(a.get(5)).isEqualTo(20);
        assertThat(a.indexOf(20)).isEqualTo(5);
        assertThat(a.contains(20)).isTrue();
        verify(a, 1, 2, 0, 0, 0, 20, 10, 0, 0);

        assertThat(a.indexOf(99)).isEqualTo(-1);
        assertThat(a.contains(99)).isFalse();

        a.resize(15);
        a.set(14, 30);
        verify(a, 1, 2, 0, 0, 0, 20, 10, 0, 0, 0, 0, 0, 0, 0, 30);

        int[] backingArray = new int[]{1, 2, 3, 4};
        a = IntArray.wrap(backingArray);
        a.set(0, 10);
        assertThat(backingArray[0]).isEqualTo(10);
        backingArray[1] = 20;
        backingArray[2] = 30;
        verify(a, backingArray);
        assertThat(a.indexOf(30)).isEqualTo(2);
        assertThat(a.contains(30)).isTrue();

        a.resize(2);
        assertThat(backingArray[2]).isEqualTo(0);
        assertThat(backingArray[3]).isEqualTo(0);

        a.add(50);
        verify(a, 10, 20, 50);
    }

    @Test
    public void testToString() {
        IntArray a = new IntArray(10);
        a.add(4);
        a.add(8);
        a.add(15);
        a.add(16);
        a.add(23);
        a.add(42);

        assertWithMessage("toString()").that(a.toString()).contains("4, 8, 15, 16, 23, 42");
        assertWithMessage("toString()").that(a.toString()).doesNotContain("0");
    }

    public void verify(IntArray intArray, int... expected) {
        assertWithMessage("contents of %s", intArray).that(intArray.toArray()).asList()
                .containsExactlyElementsIn(Arrays.stream(expected).boxed().toList());
    }
}
