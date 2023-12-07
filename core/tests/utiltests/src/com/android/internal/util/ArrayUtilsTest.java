/*
 * Copyright (C) 2013 The Android Open Source Project
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Tests for {@link ArrayUtils}
 */
@RunWith(AndroidJUnit4.class)
public class ArrayUtilsTest {
    @Test
    public void testContains() throws Exception {
        final Object A = new Object();
        final Object B = new Object();
        final Object C = new Object();
        final Object D = new Object();

        assertTrue(ArrayUtils.contains(new Object[] { A, B, C }, A));
        assertTrue(ArrayUtils.contains(new Object[] { A, B, C }, B));
        assertTrue(ArrayUtils.contains(new Object[] { A, B, C }, C));
        assertTrue(ArrayUtils.contains(new Object[] { A, null, C }, null));

        assertFalse(ArrayUtils.contains(new Object[] { A, B, C }, null));
        assertFalse(ArrayUtils.contains(new Object[] { }, null));
        assertFalse(ArrayUtils.contains(new Object[] { null }, A));
    }

    @Test
    public void testIndexOf() throws Exception {
        final Object A = new Object();
        final Object B = new Object();
        final Object C = new Object();
        final Object D = new Object();

        assertEquals(0, ArrayUtils.indexOf(new Object[] { A, B, C }, A));
        assertEquals(1, ArrayUtils.indexOf(new Object[] { A, B, C }, B));
        assertEquals(2, ArrayUtils.indexOf(new Object[] { A, B, C }, C));
        assertEquals(-1, ArrayUtils.indexOf(new Object[] { A, B, C }, D));

        assertEquals(-1, ArrayUtils.indexOf(new Object[] { A, B, C }, null));
        assertEquals(-1, ArrayUtils.indexOf(new Object[] { }, A));
        assertEquals(-1, ArrayUtils.indexOf(new Object[] { }, null));

        assertEquals(0, ArrayUtils.indexOf(new Object[] { null, null }, null));
        assertEquals(1, ArrayUtils.indexOf(new Object[] { A, null, B }, null));
        assertEquals(2, ArrayUtils.indexOf(new Object[] { A, null, B }, B));
    }

    @Test
    public void testContainsAll() throws Exception {
        final Object A = new Object();
        final Object B = new Object();
        final Object C = new Object();

        assertTrue(ArrayUtils.containsAll(new Object[] { C, B, A }, new Object[] { A, B, C }));
        assertTrue(ArrayUtils.containsAll(new Object[] { A, B }, new Object[] { A }));
        assertTrue(ArrayUtils.containsAll(new Object[] { A }, new Object[] { A }));
        assertTrue(ArrayUtils.containsAll(new Object[] { A }, new Object[] { }));
        assertTrue(ArrayUtils.containsAll(new Object[] { }, new Object[] { }));
        assertTrue(ArrayUtils.containsAll(new Object[] { null }, new Object[] { }));
        assertTrue(ArrayUtils.containsAll(new Object[] { null }, new Object[] { null }));
        assertTrue(ArrayUtils.containsAll(new Object[] { A, null, C }, new Object[] { C, null }));

        assertFalse(ArrayUtils.containsAll(new Object[] { }, new Object[] { A }));
        assertFalse(ArrayUtils.containsAll(new Object[] { B }, new Object[] { A }));
        assertFalse(ArrayUtils.containsAll(new Object[] { }, new Object[] { null }));
        assertFalse(ArrayUtils.containsAll(new Object[] { A }, new Object[] { null }));
    }

    @Test
    public void testContainsInt() throws Exception {
        assertTrue(ArrayUtils.contains(new int[] { 1, 2, 3 }, 1));
        assertTrue(ArrayUtils.contains(new int[] { 1, 2, 3 }, 2));
        assertTrue(ArrayUtils.contains(new int[] { 1, 2, 3 }, 3));

        assertFalse(ArrayUtils.contains(new int[] { 1, 2, 3 }, 0));
        assertFalse(ArrayUtils.contains(new int[] { 1, 2, 3 }, 4));
        assertFalse(ArrayUtils.contains(new int[] { }, 2));
    }

    @Test
    public void testAppendInt() throws Exception {
        assertArrayEquals(new int[] { 1 },
                ArrayUtils.appendInt(null, 1));
        assertArrayEquals(new int[] { 1 },
                ArrayUtils.appendInt(new int[] { }, 1));
        assertArrayEquals(new int[] { 1, 2 },
                ArrayUtils.appendInt(new int[] { 1 }, 2));
        assertArrayEquals(new int[] { 1, 2 },
                ArrayUtils.appendInt(new int[] { 1, 2 }, 1));
    }

    @Test
    public void testRemoveInt() throws Exception {
        assertNull(ArrayUtils.removeInt(null, 1));
        assertArrayEquals(new int[] { },
                ArrayUtils.removeInt(new int[] { }, 1));
        assertArrayEquals(new int[] { 1, 2, 3, },
                ArrayUtils.removeInt(new int[] { 1, 2, 3}, 4));
        assertArrayEquals(new int[] { 2, 3, },
                ArrayUtils.removeInt(new int[] { 1, 2, 3}, 1));
        assertArrayEquals(new int[] { 1, 3, },
                ArrayUtils.removeInt(new int[] { 1, 2, 3}, 2));
        assertArrayEquals(new int[] { 1, 2, },
                ArrayUtils.removeInt(new int[] { 1, 2, 3}, 3));
        assertArrayEquals(new int[] { 2, 3, 1 },
                ArrayUtils.removeInt(new int[] { 1, 2, 3, 1 }, 1));
    }

    @Test
    public void testContainsLong() throws Exception {
        assertTrue(ArrayUtils.contains(new long[] { 1, 2, 3 }, 1));
        assertTrue(ArrayUtils.contains(new long[] { 1, 2, 3 }, 2));
        assertTrue(ArrayUtils.contains(new long[] { 1, 2, 3 }, 3));

        assertFalse(ArrayUtils.contains(new long[] { 1, 2, 3 }, 0));
        assertFalse(ArrayUtils.contains(new long[] { 1, 2, 3 }, 4));
        assertFalse(ArrayUtils.contains(new long[] { }, 2));
    }

    @Test
    public void testAppendLong() throws Exception {
        assertArrayEquals(new long[] { 1 },
                ArrayUtils.appendLong(null, 1));
        assertArrayEquals(new long[] { 1 },
                ArrayUtils.appendLong(new long[] { }, 1));
        assertArrayEquals(new long[] { 1, 2 },
                ArrayUtils.appendLong(new long[] { 1 }, 2));
        assertArrayEquals(new long[] { 1, 2 },
                ArrayUtils.appendLong(new long[] { 1, 2 }, 1));
    }

    @Test
    public void testAppendBoolean() throws Exception {
        assertArrayEquals(new boolean[] { true },
                ArrayUtils.appendBoolean(null, true));
        assertArrayEquals(new boolean[] { true },
                ArrayUtils.appendBoolean(new boolean[] { }, true));
        assertArrayEquals(new boolean[] { true, false },
                ArrayUtils.appendBoolean(new boolean[] { true }, false));
        assertArrayEquals(new boolean[] { true, true },
                ArrayUtils.appendBoolean(new boolean[] { true }, true));
    }

    @Test
    public void testRemoveLong() throws Exception {
        assertNull(ArrayUtils.removeLong(null, 1));
        assertArrayEquals(new long[] { },
                ArrayUtils.removeLong(new long[] { }, 1));
        assertArrayEquals(new long[] { 1, 2, 3, },
                ArrayUtils.removeLong(new long[] { 1, 2, 3}, 4));
        assertArrayEquals(new long[] { 2, 3, },
                ArrayUtils.removeLong(new long[] { 1, 2, 3}, 1));
        assertArrayEquals(new long[] { 1, 3, },
                ArrayUtils.removeLong(new long[] { 1, 2, 3}, 2));
        assertArrayEquals(new long[] { 1, 2, },
                ArrayUtils.removeLong(new long[] { 1, 2, 3}, 3));
        assertArrayEquals(new long[] { 2, 3, 1 },
                ArrayUtils.removeLong(new long[] { 1, 2, 3, 1 }, 1));
    }

    @Test
    public void testConcat_zeroObjectArrays() {
        // empty varargs array
        assertArrayEquals(new String[] {}, ArrayUtils.concat(String.class));
        // null varargs array
        assertArrayEquals(new String[] {}, ArrayUtils.concat(String.class, (String[][]) null));
    }

    @Test
    public void testConcat_oneObjectArray() {
        assertArrayEquals(new String[] { "1", "2" },
                ArrayUtils.concat(String.class, new String[] { "1", "2" }));
    }

    @Test
    public void testConcat_oneEmptyObjectArray() {
        assertArrayEquals(new String[] {}, ArrayUtils.concat(String.class, (String[]) null));
        assertArrayEquals(new String[] {}, ArrayUtils.concat(String.class, new String[] {}));
    }

    @Test
    public void testConcat_twoObjectArrays() {
        assertArrayEquals(new Long[] { 1L },
                ArrayUtils.concat(Long.class, new Long[] { 1L }, new Long[] {}));
        assertArrayEquals(new Long[] { 1L },
                ArrayUtils.concat(Long.class, new Long[] {}, new Long[] { 1L }));
        assertArrayEquals(new Long[] { 1L, 2L },
                ArrayUtils.concat(Long.class, new Long[] { 1L }, new Long[] { 2L }));
        assertArrayEquals(new Long[] { 1L, 2L, 3L, 4L },
                ArrayUtils.concat(Long.class, new Long[] { 1L, 2L }, new Long[] { 3L, 4L }));
    }

    @Test
    public void testConcat_twoEmptyObjectArrays() {
        assertArrayEquals(new Long[] {}, ArrayUtils.concat(Long.class, null, null));
        assertArrayEquals(new Long[] {}, ArrayUtils.concat(Long.class, new Long[] {}, null));
        assertArrayEquals(new Long[] {}, ArrayUtils.concat(Long.class, null, new Long[] {}));
        assertArrayEquals(new Long[] {},
                ArrayUtils.concat(Long.class, new Long[] {}, new Long[] {}));
    }

    @Test
    public void testConcat_threeObjectArrays() {
        String[] array1 = { "1", "2" };
        String[] array2 = { "3", "4" };
        String[] array3 = { "5", "6" };
        String[] expectation = { "1", "2", "3", "4", "5", "6" };

        assertArrayEquals(expectation, ArrayUtils.concat(String.class, array1, array2, array3));
    }

    @Test
    public void testConcat_threeObjectArraysWithNull() {
        String[] array1 = { "1", "2" };
        String[] array2 = null;
        String[] array3 = { "5", "6" };
        String[] expectation = { "1", "2", "5", "6" };

        assertArrayEquals(expectation, ArrayUtils.concat(String.class, array1, array2, array3));
    }

    @Test
    public void testConcat_zeroByteArrays() {
        // empty varargs array
        assertArrayEquals(new byte[] {}, ArrayUtils.concat());
        // null varargs array
        assertArrayEquals(new byte[] {}, ArrayUtils.concat((byte[][]) null));
    }

    @Test
    public void testConcat_oneByteArray() {
        assertArrayEquals(new byte[] { 1, 2 }, ArrayUtils.concat(new byte[] { 1, 2 }));
    }

    @Test
    public void testConcat_oneEmptyByteArray() {
        assertArrayEquals(new byte[] {}, ArrayUtils.concat((byte[]) null));
        assertArrayEquals(new byte[] {}, ArrayUtils.concat(new byte[] {}));
    }

    @Test
    public void testConcat_twoByteArrays() {
        assertArrayEquals(new byte[] { 1 }, ArrayUtils.concat(new byte[] { 1 }, new byte[] {}));
        assertArrayEquals(new byte[] { 1 }, ArrayUtils.concat(new byte[] {}, new byte[] { 1 }));
        assertArrayEquals(new byte[] { 1, 2 },
                ArrayUtils.concat(new byte[] { 1 }, new byte[] { 2 }));
        assertArrayEquals(new byte[] { 1, 2, 3, 4 },
                ArrayUtils.concat(new byte[] { 1, 2 }, new byte[] { 3, 4 }));
    }

    @Test
    public void testConcat_twoEmptyByteArrays() {
        assertArrayEquals(new byte[] {}, ArrayUtils.concat((byte[]) null, null));
        assertArrayEquals(new byte[] {}, ArrayUtils.concat(new byte[] {}, null));
        assertArrayEquals(new byte[] {}, ArrayUtils.concat((byte[]) null, new byte[] {}));
        assertArrayEquals(new byte[] {}, ArrayUtils.concat(new byte[] {}, new byte[] {}));
    }

    @Test
    public void testConcat_threeByteArrays() {
        byte[] array1 = { 1, 2 };
        byte[] array2 = { 3, 4 };
        byte[] array3 = { 5, 6 };
        byte[] expectation = { 1, 2, 3, 4, 5, 6 };

        assertArrayEquals(expectation, ArrayUtils.concat(array1, array2, array3));
    }

    @Test
    public void testConcat_threeByteArraysWithNull() {
        byte[] array1 = { 1, 2 };
        byte[] array2 = null;
        byte[] array3 = { 5, 6 };
        byte[] expectation = { 1, 2, 5, 6 };

        assertArrayEquals(expectation, ArrayUtils.concat(array1, array2, array3));
    }

    @Test
    @SmallTest
    public void testUnstableRemoveIf() throws Exception {
        java.util.function.Predicate<Object> isNull = new java.util.function.Predicate<Object>() {
            @Override
            public boolean test(Object o) {
                return o == null;
            }
        };

        final Object a = new Object();
        final Object b = new Object();
        final Object c = new Object();

        ArrayList<Object> collection = null;
        assertEquals(0, ArrayUtils.unstableRemoveIf(collection, isNull));

        collection = new ArrayList<>();
        assertEquals(0, ArrayUtils.unstableRemoveIf(collection, isNull));

        collection = new ArrayList<>(Collections.singletonList(a));
        assertEquals(0, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Collections.singletonList(null));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(0, collection.size());

        collection = new ArrayList<>(Arrays.asList(a, b));
        assertEquals(0, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(2, collection.size());
        assertTrue(collection.contains(a));
        assertTrue(collection.contains(b));

        collection = new ArrayList<>(Arrays.asList(a, null));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Arrays.asList(null, a));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Arrays.asList(null, null));
        assertEquals(2, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(0, collection.size());

        collection = new ArrayList<>(Arrays.asList(a, b, c));
        assertEquals(0, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(3, collection.size());
        assertTrue(collection.contains(a));
        assertTrue(collection.contains(b));
        assertTrue(collection.contains(c));

        collection = new ArrayList<>(Arrays.asList(a, b, null));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(2, collection.size());
        assertTrue(collection.contains(a));
        assertTrue(collection.contains(b));

        collection = new ArrayList<>(Arrays.asList(a, null, b));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(2, collection.size());
        assertTrue(collection.contains(a));
        assertTrue(collection.contains(b));

        collection = new ArrayList<>(Arrays.asList(null, a, b));
        assertEquals(1, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(2, collection.size());
        assertTrue(collection.contains(a));
        assertTrue(collection.contains(b));

        collection = new ArrayList<>(Arrays.asList(a, null, null));
        assertEquals(2, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Arrays.asList(null, null, a));
        assertEquals(2, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Arrays.asList(null, a, null));
        assertEquals(2, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(1, collection.size());
        assertTrue(collection.contains(a));

        collection = new ArrayList<>(Arrays.asList(null, null, null));
        assertEquals(3, ArrayUtils.unstableRemoveIf(collection, isNull));
        assertEquals(0, collection.size());
    }

    @Test
    @SmallTest
    public void testThrowsIfOutOfBounds_passesWhenRangeInsideArray() {
        ArrayUtils.throwsIfOutOfBounds(10, 2, 6);
    }

    @Test
    @SmallTest
    public void testThrowsIfOutOfBounds_passesWhenRangeIsWholeArray() {
        ArrayUtils.throwsIfOutOfBounds(10, 0, 10);
    }

    @Test
    @SmallTest
    public void testThrowsIfOutOfBounds_passesWhenEmptyRangeAtStart() {
        ArrayUtils.throwsIfOutOfBounds(10, 0, 0);
    }

    @Test
    @SmallTest
    public void testThrowsIfOutOfBounds_passesWhenEmptyRangeAtEnd() {
        ArrayUtils.throwsIfOutOfBounds(10, 10, 0);
    }

    @Test
    @SmallTest
    public void testThrowsIfOutOfBounds_passesWhenEmptyArray() {
        ArrayUtils.throwsIfOutOfBounds(0, 0, 0);
    }

    @Test
    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenRangeStartNegative() {
        try {
            ArrayUtils.throwsIfOutOfBounds(10, -1, 5);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }

    @Test
    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenCountNegative() {
        try {
            ArrayUtils.throwsIfOutOfBounds(10, 5, -1);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }

    @Test
    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenRangeStartTooHigh() {
        try {
            ArrayUtils.throwsIfOutOfBounds(10, 11, 0);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }

    @Test
    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenRangeEndTooHigh() {
        try {
            ArrayUtils.throwsIfOutOfBounds(10, 5, 6);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }

    @Test
    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenLengthNegative() {
        try {
            ArrayUtils.throwsIfOutOfBounds(-1, 0, 0);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }

    @Test
    @SmallTest
    public void testThrowsIfOutOfBounds_failsWhenOverflowRangeEndTooHigh() {
        try {
            ArrayUtils.throwsIfOutOfBounds(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
            fail();
        } catch (ArrayIndexOutOfBoundsException expected) {
            // expected
        }
    }
}
