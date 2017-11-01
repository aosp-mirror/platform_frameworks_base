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

package com.android.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.Objects;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class RingBufferTest {

    @Test
    public void testEmptyRingBuffer() {
        RingBuffer<String> buffer = new RingBuffer<>(String.class, 100);

        assertArraysEqual(new String[0], buffer.toArray());
    }

    @Test
    public void testIncorrectConstructorArguments() {
        try {
            RingBuffer<String> buffer = new RingBuffer<>(String.class, -10);
            fail("Should not be able to create a negative capacity RingBuffer");
        } catch (IllegalArgumentException expected) {
        }

        try {
            RingBuffer<String> buffer = new RingBuffer<>(String.class, 0);
            fail("Should not be able to create a 0 capacity RingBuffer");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testRingBufferWithNoWrapping() {
        RingBuffer<String> buffer = new RingBuffer<>(String.class, 100);

        buffer.append("a");
        buffer.append("b");
        buffer.append("c");
        buffer.append("d");
        buffer.append("e");

        String[] expected = {"a", "b", "c", "d", "e"};
        assertArraysEqual(expected, buffer.toArray());
    }

    @Test
    public void testRingBufferWithCapacity1() {
        RingBuffer<String> buffer = new RingBuffer<>(String.class, 1);

        buffer.append("a");
        assertArraysEqual(new String[]{"a"}, buffer.toArray());

        buffer.append("b");
        assertArraysEqual(new String[]{"b"}, buffer.toArray());

        buffer.append("c");
        assertArraysEqual(new String[]{"c"}, buffer.toArray());

        buffer.append("d");
        assertArraysEqual(new String[]{"d"}, buffer.toArray());

        buffer.append("e");
        assertArraysEqual(new String[]{"e"}, buffer.toArray());
    }

    @Test
    public void testRingBufferWithWrapping() {
        int capacity = 100;
        RingBuffer<String> buffer = new RingBuffer<>(String.class, capacity);

        buffer.append("a");
        buffer.append("b");
        buffer.append("c");
        buffer.append("d");
        buffer.append("e");

        String[] expected1 = {"a", "b", "c", "d", "e"};
        assertArraysEqual(expected1, buffer.toArray());

        String[] expected2 = new String[capacity];
        int firstIndex = 0;
        int lastIndex = capacity - 1;

        expected2[firstIndex] = "e";
        for (int i = 1; i < capacity; i++) {
            buffer.append("x");
            expected2[i] = "x";
        }
        assertArraysEqual(expected2, buffer.toArray());

        buffer.append("x");
        expected2[firstIndex] = "x";
        assertArraysEqual(expected2, buffer.toArray());

        for (int i = 0; i < 10; i++) {
            for (String s : expected2) {
                buffer.append(s);
            }
        }
        assertArraysEqual(expected2, buffer.toArray());

        buffer.append("a");
        expected2[lastIndex] = "a";
        assertArraysEqual(expected2, buffer.toArray());
    }

    static <T> void assertArraysEqual(T[] expected, T[] got) {
        if (expected.length != got.length) {
            fail(Arrays.toString(expected) + " and " + Arrays.toString(got)
                    + " did not have the same length");
        }

        for (int i = 0; i < expected.length; i++) {
            if (!Objects.equals(expected[i], got[i])) {
                fail(Arrays.toString(expected) + " and " + Arrays.toString(got)
                        + " were not equal");
            }
        }
    }
}
