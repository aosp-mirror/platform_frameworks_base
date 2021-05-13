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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class RingBufferTest {

    @Test
    public void testEmptyRingBuffer() {
        RingBuffer<String> buffer = new RingBuffer<>(String.class, 100);

        assertArrayEquals(new String[0], buffer.toArray());
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
        assertArrayEquals(expected, buffer.toArray());
    }

    @Test
    public void testRingBufferWithCapacity1() {
        RingBuffer<String> buffer = new RingBuffer<>(String.class, 1);

        buffer.append("a");
        assertArrayEquals(new String[]{"a"}, buffer.toArray());

        buffer.append("b");
        assertArrayEquals(new String[]{"b"}, buffer.toArray());

        buffer.append("c");
        assertArrayEquals(new String[]{"c"}, buffer.toArray());

        buffer.append("d");
        assertArrayEquals(new String[]{"d"}, buffer.toArray());

        buffer.append("e");
        assertArrayEquals(new String[]{"e"}, buffer.toArray());
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
        assertArrayEquals(expected1, buffer.toArray());

        String[] expected2 = new String[capacity];
        int firstIndex = 0;
        int lastIndex = capacity - 1;

        expected2[firstIndex] = "e";
        for (int i = 1; i < capacity; i++) {
            buffer.append("x");
            expected2[i] = "x";
        }
        assertArrayEquals(expected2, buffer.toArray());

        buffer.append("x");
        expected2[firstIndex] = "x";
        assertArrayEquals(expected2, buffer.toArray());

        for (int i = 0; i < 10; i++) {
            for (String s : expected2) {
                buffer.append(s);
            }
        }
        assertArrayEquals(expected2, buffer.toArray());

        buffer.append("a");
        expected2[lastIndex] = "a";
        assertArrayEquals(expected2, buffer.toArray());
    }

    @Test
    public void testGetNextSlot() {
        int capacity = 100;
        RingBuffer<DummyClass1> buffer = new RingBuffer<>(DummyClass1.class, capacity);

        final DummyClass1[] actual = new DummyClass1[capacity];
        final DummyClass1[] expected = new DummyClass1[capacity];
        for (int i = 0; i < capacity; ++i) {
            final DummyClass1 obj = buffer.getNextSlot();
            obj.x = capacity * i;
            actual[i] = obj;
            expected[i] = new DummyClass1();
            expected[i].x = capacity * i;
        }
        assertArrayEquals(expected, buffer.toArray());

        for (int i = 0; i < capacity; ++i) {
            if (actual[i] != buffer.getNextSlot()) {
                fail("getNextSlot() should re-use objects if available");
            }
        }

        RingBuffer<DummyClass2> buffer2 = new RingBuffer<>(DummyClass2.class, capacity);
        assertNull("getNextSlot() should return null if the object can't be initiated "
                + "(No nullary constructor)", buffer2.getNextSlot());

        RingBuffer<DummyClass3> buffer3 = new RingBuffer<>(DummyClass3.class, capacity);
        assertNull("getNextSlot() should return null if the object can't be initiated "
                + "(Inaccessible class)", buffer3.getNextSlot());
    }

    public static final class DummyClass1 {
        int x;

        public boolean equals(Object o) {
            if (o instanceof DummyClass1) {
                final DummyClass1 other = (DummyClass1) o;
                return other.x == this.x;
            }
            return false;
        }
    }

    public static final class DummyClass2 {
        public DummyClass2(int x) {}
    }

    private static final class DummyClass3 {}
}
